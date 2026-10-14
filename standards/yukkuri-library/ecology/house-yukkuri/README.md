# 家油 · 魔理沙与灵梦

2026-10-13：家油魔理沙随标准改为单侧粗辫，另一侧为短侧发；家油身体与灵梦不变。夹取、搬运、放下改由辫子单独完成：两个夹取点都指向辫梢，短侧发留在头侧；同步游戏模型、伤口表面和夹取曲线。家油灵梦仍用两束短发夹取。造型和夹取待复查，见[单辫修订](../../body-standard/features/marisa-single-braid/README.md)。

[打开当前预览](previews/current/index.html) · [多角度图片](previews/current/turnaround.png) · [参考核读](REFERENCES.md) · [设计参数](config.json)

当前造型为 v9，预览状态修正为 v9.1，**造型待确认**。佩尼佩尼按当前身体宽度与标准身体宽度之比 4.32 / 12 缩放为 0.36，并重算伸出行程，使粗根露出且仍嵌在腹面。麻姆麻姆采用独立挂点、后腹斜率与 0.10 单位行程，收回时藏入身体，伸出后仅露贴面标记。

身体设计尺寸为宽 4.32、高 3.82、深 2.93 单位，最宽截面位于离地 1.12 单位处。身体改为共享顶点的连续三角面，取消分块端面形成的台阶；前部回收集中在离地 0.35 单位以下，保留低多边形折面与落地圆角。正面承托宽度覆盖现有眼睛和腮红。

头发沿卵形的宽度和深度逐层收拢，连接圆顶、太阳穴与后脑；后发下缘距地面为灵梦 1.84、魔理沙 1.72 单位，侧后转角略短，露出下腹。借用 DOS 魔理沙的分层贴头思路，采用较短发尾。两侧发束和发饰前移、外移，露出短尾；魔理沙帽子整体下移 0.40 单位，帽下发冠收在 3.50 单位高度内，避免头发穿出帽沿上方。

两种家油共用一个身体网格（96 顶点、188 三角面）；油菊使用贴合后腹的独立曲面。灵梦蝴蝶结外移，飘带按后发深度逐段贴合并保留纵向 UV。其余部分仍为方块，11 种表情图集及普通走动、受惊曲线沿用标准素材；四段伸缩动作使用本轮家油专用行程。预览骨架按每个元素的实际顶点数量处理动画，兼容已有方块预览。

常态下两个伸缩部件都完全收回。切换观察对象、生活片段、重播或恢复场景时立即清除伸出状态；手动点击“收回”仍播放收回过程。部件按钮下方显示“已收回（常态）”或当前伸缩阶段。预览入口按内页内容指纹更新 iframe 地址；已经打开的旧页需重新打开或刷新。

预览提供双角色、单角色、普通成体对比与巢卵观察，以及五种生活片段。支持旋转、缩放、拖动时间、暂停和家具显隐。孵化后幼体保留独立身份，移除巢不会删除已经出生的幼体。

这些是**美术资产与浏览器生态原型**，尚未接入 Minecraft 实体、服务器 AI、游戏存档和多人同步；18 秒、三枚卵是演示安排。普通宠物进入室内不会触发家油分类，普通种茎生和胎生也不调用本模块。

## 编辑与重建

家油专用身体和装配参数在 `reference_design.py`。`BODY_PROFILE` 记录高度、半宽、后深和前深；前深同时定义脸部与下腹。`HAIR_PROFILE`、`HAIR_HEM` 与 `HAIR_LAYER_HEIGHT` 定义发层轮廓、下缘和分段高度。`make_hair()` 制作包覆发层与刘海，`transform_side_point()` 适配侧发及支点。饰品、发色和表情通过角色组件清单从标准导出读取，不修改共用源。

`HEAD_OFFSET`、`HAT_OFFSET`、`RUMP_OFFSET` 控制对应部件挂接，基础缩放为 0.24；`PENI_SCALE`、`PENI_OFFSET` 与 `PENI_TRAVEL` 定义前部比例、收回位置与行程，`MAMU_OFFSET`、`MAMU_ROTATION` 与 `MAMU_TRAVEL` 将后部独立贴合腹面；`HAT_HAIR_TOP` 限定帽下发冠高度。五官以 `FACE_ANCHOR` 为中心，在宽高方向按 `FACE_SCALE=0.33` 放大，输出中心为 `FACE_OUTPUT`。标准角色源仍由本库 `characters/`、`components/`、`motions/` 和 `expressions/` 维护。来源、设计参数和角色导出文件哈希登记于 [provenance.json](assembled/provenance.json)。

仅调整家油造型时，先导出角色并刷新预览数据，再用预览自身的渲染器生成多角度图片。保留既有道具和普通成体对照数据，离线出图不需要浏览器或服务器：

```powershell
python standards/yukkuri-library/ecology/house-yukkuri/build.py --characters-only
python standards/yukkuri-library/ecology/house-yukkuri/render_review.py --label v9
```

`render_review.py` 使用已有 Node.js、Pillow 和本库 `runtime/render.js`、`runtime/motion.js`，输出两角色的正面、斜面、侧面、背面、缺陷近景、走动采样及图片指纹清单。标准角色源发生变化时，先更新基础库，再按需要重新导出完整场景和运行对应验证：

```powershell
python standards/yukkuri-library/ecology/house-yukkuri/build.py
python standards/yukkuri-library/ecology/house-yukkuri/validate.py
node standards/yukkuri-library/ecology/house-yukkuri/verify-motion.cjs
node standards/yukkuri-library/ecology/house-yukkuri/verify-anatomy.cjs
node standards/yukkuri-library/ecology/house-yukkuri/verify-default-view.cjs
# 需要另行验收浏览器交互时运行 verify.cjs
```

`mesh_geometry.py` 负责连续网格与多边形导出；`reference_design.py` 管理身体截面、组件装配和动作适配；`config.json` 登记尺寸与 11 个统一表情 ID；`build.py` 从标准导出读取全部饰品和表情纹理，生成模型与道具。`lifecycle.js` 管理显式时间状态，`viewer.js` 管理预览交互。修改源文件后重新构建，生成的 `assembled/` 和 `index.html` 用于导出与查看。

[伸缩状态预览](previews/current/anatomy-review.png)按收回、半伸、全伸排列。[挂接检查](reports/current/anatomy-consistency.json)核对收回埋入、前部根部相交、后部贴面间隙及控制器与导出终点的一致性。后部全伸标记距腹面约 0.004～0.012 单位；这些数值为制作参数。

## 导出

身体与油菊曲面同时写入 BBModel、预览和 `.geo.json`。`.geo.json` 的 `poly_mesh` 是旧式交换表示；[Microsoft 文档](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/geometry.v1.12.0?view=minecraft-bedrock-stable)已将其标为弃用，不能视作可直接运行的 Bedrock 或 GeckoLib 资源。Minecraft 接入需选择支持网格的渲染路径或另做方块适配，尚未验收。

- [家油魔理沙](assembled/house_marisa.bbmodel)、[家油灵梦](assembled/house_reimu.bbmodel)：可编辑 BBModel、带旧式 `poly_mesh` 的几何交换文件、动画、绑定骨架和 11 种标准表情图集；九骨骼为世界位移、主体、头部、五官、左右辫子、油臀与两个伸缩部件。走动与受惊动作复用素材库的上下形变、摇摆及世界位移曲线。两个伸缩部件复用统一控制器，默认收回、互斥切换，暂停生活片段时仍可操作；同时导出四段伸出／收回动画。幼体预览不响应成体部件控制。
- [棉絮巢](assembled/lint_nest.bbmodel)、[卵](assembled/house_egg.bbmodel)、[壳片](assembled/house_eggshell.bbmodel)与另外三种道具：同目录提供 PNG、BBModel、几何与绑定骨架。
- 生活片段的多对象路线与孵化事件由 JavaScript 驱动，未烘焙为单体动画；导入游戏需移植服务端状态和场景协调。单体模型动画已经导出。

坐标每格 16 单位、+Y 向上、−Z 为正面。v9 的导出一致性核对见 [静态一致性记录](reports/current/static-consistency.json)，本轮多角度目视结果见 [造型审查](reports/current/shape-review.json)。[动画数据检查](reports/current/motion-consistency.json)对所有导出动作做了三个时刻的采样对照。造型审查只覆盖所列视角，标准造型仍待用户确认；[常态浏览器检查](reports/current/default-browser.json)已验证六种观察对象的常态画面，以及伸出后切换对象、生活片段、重播和恢复场景的立即收回。其他浏览器交互、Blockbench 导入、性能与 Minecraft 运行未验收。

当前造型以 `previews/current/`、`index.html` 和 `assembled/` 为准；本目录根部 PNG、`verification.json` 与 `browser-verification.json` 属于 v3 历史证据。人工查看可直接打开当前预览，观察下宽轮廓、脸身连续性和帽沿贴合；再切换惊讶表情及走动片段查看其他遮挡与挂接表现。
