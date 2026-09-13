# 周边素材候选 · R3.1

2026-09-13：完整家油卵与裂壳卵 R2.4 已由用户确认，制作源、模型、贴图与预览已冻结保存，见[家油卵标准造型](../../ecology/house-egg/README.md)。其他周边素材保持待确认；游戏接入状态不因造型确认而改变。

[全身伤口与多处挂接](WOUND-PLACEMENT.md) · [身体伤情与效果说明](BODY-EFFECTS.md) · [身体效果总览](previews/current/body-sheet.png) · [统一交互预览](previews/current/index.html) · [方块总览](previews/current/block-sheet.png) · [物品总览](previews/current/item-sheet.png) · [附属物总览](previews/current/attachment-sheet.png)

2026-09-13。按用户本轮修订及[周边美术清单](../../../../docs/PROP_ART_INVENTORY.md)制作自有模型与材质，当前为**R3.1 混合确认素材：家油卵已确认，其他造型待确认**。三档盆内油粮共用外观，草料沿用原版来源，剪成横放短草段与散落麦穗；垃圾改为鱼骨、果核、蛋壳、皱纸和果皮。观察手册直接复用原版书外观。原版短草、高草、小麦已通过食物标签接入现有喂食路径；宠物盆等新模型、尺寸、数量和短茎联动仍只在素材预览中实现，未更新已确认标准指纹。

## 分类与范围

| 分类 | 内容 | 展示数量 |
| --- | --- | --- |
| 方块类 | 宠物食盆、草窝、沙厕、家油缝隙、埋葬土堆 | 5 种设施，12 个状态；食盆有空盆、普通粮、廉价粮、高级粮、剪短散草、横放麦穗、垃圾，沙厕有干净／脏污 |
| 物品类 | 三档袋装油粮、食物残渣、独立便便、漏出馅料、携带篮、清洗布、教导哨、毛线球、结伴绳、橙汁、去霉药、观察手册、脱落蝴蝶结、脱落魔理沙帽 | 16 个模型；两类绷带均取消，遗体待做 |
| 附属物 | 棉絮巢垫、竖长家油卵、灵梦／魔理沙短茎繁殖组合、缩短枯茎、伤口与馅料贴面 | 7 个展示条目；卵有完整／裂壳，两种繁殖组合可切换 1–5 胎 |
| 伤情与效果 | 擦伤、裂口、凹陷、受压、焦痕、干瘪、油霉和漏馅过程 | 9 个条目：8 个分级示例及全身伤口编辑器；保留 64 份示例，另导出两角色六部位及多处组合共 14 份场景 |

分类描述物品用途，不以模型形状判断代码类型。食盆等设施放入背包时仍有方块物品；家油卵、垫材和身体部件在本批中不作为独立可拾取物品。独立便便在厕所外也有自己的模型；袋装油粮、橙汁、去霉药是新增候选，尚未注册为游戏物品。

茎上幼体继续复用当前接入的角色模型。子油候选比例由 0.30 改为 0.18；1–5 胎茎长度系数由 `[0.95,0.95,1.70,2.50,3.40]` 改为 `[0.72,0.82,1.08,1.38,1.72]`，保留向前弯曲的路线。当前组合包含真实母体、子油、茎和短挂点，预览可切换 1–5 胎；可编辑下载是默认三胎组合。已查看两种角色一、三、五胎多角度，未发现实际穿插。游戏中的既有版本仍见[家庭茎生说明](../../../../art/props/family-life/README.md)，本轮未修改该运行配方。

遗体按用户要求标为待做，旧侧卧模型不再出现在当前列表。后续需要按死因、损伤程度和种类分别设计。绷带物品、身体绷带和草药束已从当前候选中移除，照料重点改为橙汁与去霉药。

## 联动展示与药效定义

| 对象 | 当前素材预览 | 后续游戏接入需要读取的实际数据 |
| --- | --- | --- |
| 独立便便 | 详情页体型滑块 0.24–1.40，线性尺寸同比缩放 | 排出者的实际体型；掉落后需保留，不以附近实体代替 |
| 埋葬土堆 | 同一体型缩放规则 | 被埋葬者的体型；放置后需持久化 |
| 脱落头饰 | 保留角色部件几何，随体型缩放 | 原佩戴者体型，与身份和饰物令牌一起保留 |
| 漏出馅料 | 0–12 份独立团块；0 份完全不显示 | 实际漏出单位数，不用固定大团替代不同数量 |
| 食盆 | 三档油粮共用相同颗粒几何与贴图；剪短散草、横放麦穗和鱼骨厨余分别显示，份量可调 0–8；满盆分层堆高至盆沿以上，少量仍留在盆底 | 真实内容种类与剩余份量；油粮档次由物品属性区分 |
| 橙汁 | 自绘橙子标签的专用瓶装模型 | 按用户设定治愈所有物理性创伤；本轮没有把药效接入 Java |
| 去霉药 | 自绘油霉图案与划除标记的专用药瓶 | 按用户设定清除油霉；不能将现有普通伤口感染直接等同油霉 |

以上定义集中在 [presentation-rules.json](presentation-rules.json)，[游戏接入差异](INTEGRATION.md)明确记录当前代码缺口。滑块是实际数据绑定的预览输入，不代表已经接入运行存档。

## 制作源与导出

- [build.py](build.py) 生成可复用的基础几何，随后调用 [revision2.py](revision2.py)替换本轮改动并排除取消项目；原版草料与书本预览由 [native_forage.py](native_forage.py)生成。R3 身体效果由 [body_effects.py](body_effects.py)生成，详细参数和角色指纹见 [body-effects-recipe.json](body-effects-recipe.json)。全身伤口由 [wound-surface.js](wound-surface.js)统一生成皮面、局部变形、伤情与漏馅，并由 [export-wound-scenes.cjs](export-wound-scenes.cjs)导出。几何采用连续旋转面、平行传输管线、薄曲面及必要方盒；单位为每格 16，+Y 向上、−Z 为正面。
- [palette.png](palette.png) 是本批自绘色板和像素肌理；袋装油粮和药瓶的宣传图、文字标签也由制作源绘制。短草、小麦与观察手册按用户要求复用本机 Minecraft 1.21.1 原始贴图，仅嵌入本地候选预览及配套编辑文件；散草色从原版短草像素采样，预览染色固定为 `#91bd59`；剪短横放的几何由本包制作，不保留竖直草丛。游戏继续使用原版物品渲染与资源包，不复制这些像素到模组运行资源。其他新道具采用自制材质；脱落饰物及繁殖组合引用项目当前角色自制贴图。
- 每件素材在 `assembled/<id>/` 配套保存 `.bbmodel`、`.mesh.json`、`.obj`、`.mtl` 和 `texture.png`。Blockbench 文件内嵌贴图，可独立传递；OBJ 与 MTL、贴图一起使用。
- [catalog.json](catalog.json) 登记分类、尺寸、状态、联动输入和角色引用来源；[导出记录](reports/current/exports.json)保存制作脚本、联动配方、贴图和每件导出的 SHA-256。
- 修改模型应回到制作源，重新导出预览；不能只修改生成结果。人工修改 `.bbmodel` 后需要同步回制作源。

这些 `.bbmodel` 使用自由网格，`.mesh.json` 是本项目预览交换格式，**不是可直接塞入原版 Java 方块模型目录的 JSON**。后续接入需为静态设施和物品选择对应网格渲染路径，或另做符合 Java 模型约束的适配；本次未修改客户端加载器。

重建命令，在项目根目录运行：

```powershell
python standards/yukkuri-library/drafts/peripheral-props/build.py
node standards/yukkuri-library/drafts/peripheral-props/render-review.cjs
node standards/yukkuri-library/drafts/peripheral-props/render-body-review.cjs
node standards/yukkuri-library/drafts/peripheral-props/test-wound-surface.cjs
node standards/yukkuri-library/drafts/peripheral-props/render-wound-review.cjs
```

Python 使用 Pillow；原版草料读取本机 Gradle 缓存的 `minecraft_1.21.1_client.jar`，可用 `MC_CLIENT_JAR` 指定路径。只检查食盆与手册可追加 `--food-only`，只检查卵可追加 `--egg-only`。浏览器核对复用项目已有 Playwright 和本机 Chrome。预览页面没有 CDN 或服务依赖，可以直接打开本地 HTML；卡片按各自边界适配画框，详情页显示实际单位尺寸。

## 参考与设计判断

R2 阶段核读本机《ゆっくり八景U》**U9.8** 的 `sharedassets0.assets`；没有把 v8.3 的 Sprite ID 当作 v9.8 ID 使用。映射来自 `E:/yukkuri/Eight景U-ModLab/wiki/art/native-sprite-index.json`，再从 U9.8 的实际 assets 提取并看图。原始图只存放在项目 `.local/reference/peripheral-props/`，不进入贴图或导出包。

| 原始图层 | 本轮实际观察 | 用途与边界 |
| --- | --- | --- |
| Sprite 5316，`うんこ-でふぉ`；Sprite 6396，`うんこ` | 本轮直接查看独立便便图：宽底、圆背、深紫褐色，尖端翘起 | 本轮独立便便参考；提取图存放 `.local/reference/peripheral-props-r2/`，未进入发行材质 |
| Sprite 4521，`うんこ のコピー 2`；映射字段 `正面満腹２頭うんこグラベース[0]` | 第一轮查看的头上便便图层，低矮深紫褐色团块 | 只保留为前轮来源，不能用它替代本轮对独立便便图的核读 |
| Sprite 6843，`自動給餌器` | 直立圆柱粮仓、前面控制面板、下部托盘 | 识别为自动给饵器，未把它当作普通浅食盆。本批未新增自动给饵器 |

油粮包装、宣传图和三档共用颗粒由本次制作，食盆改为外扩底座、卷边和防滑底的不锈钢宠物盆，不沿用前轮陶瓷浅碗。家油卵在 R2.4 重新制作连续卵壳曲线，下部饱满、上端略收，去掉原有平顶和平底，高宽比约 1.6；本轮没有重新访问[既有家油参考记录](../../ecology/house-yukkuri/REFERENCES.md)中的外部作品。原参考图的方块堆叠表现不作为本轮连续曲面候选的验收依据。

蝴蝶结、帽子从 `art/source/standard-runtime/<species>/runtime.model.json` 按饰物部件提取，保留对应贴图；本批没有改动原角色模型。其他工具、垫窝、医疗用品的立体形态属于本次设计。

## 已执行检查

R3.1 全身伤口的静态检查、点击定位、独立编辑、下载与动作检查见 [全身伤口说明](WOUND-PLACEMENT.md)。原有身体效果检查见 [body-browser.json](reports/current/body-browser.json)和[身体视觉记录](reports/current/body-visual-review.md)，覆盖两角色、分级、体型、漏馅数量与播放。旧周边复核由 [browser.json](reports/current/browser.json)记录，重生成完整卵和裂壳卵四视角，并检查分类、搜索、状态切换、旋转、缩放、复位、Esc 及移动布局。卵壳顶底收合及目视范围见[视觉记录](reports/current/visual-review.md)。其他体型、漏馅和食盆检查保留在其原版本的明确范围内，未宣称本轮重新运行。食盆满量几何记录仍见 [bowl-fill.json](reports/current/bowl-fill.json)。

R2 独立审查核对短茎组合、包装及药瓶标签、竖长卵和分类，未发现需要返工的明显几何缺陷。当前卵形替代了 R2 阶段的卵；旧卵及相应检查保存在 [archive/egg-r2.3.zip](archive/egg-r2.3.zip)，不再以旧结论证明新卵形。R2.2 被替代的稀疏食盆基线保存在 [archive/food-r2.2.zip](archive/food-r2.2.zip)，只保留作迭代对照。R2.1 被替代的直立草丛、旧垃圾与专用书封面保存在 [archive/food-r2.1.zip](archive/food-r2.1.zip)，其中的旧视觉结论不证明当前造型符合要求。R2 食盆修改前基线保存在 [archive/food-r2.zip](archive/food-r2.zip)。第一轮被替代的完整候选保存在 [archive/r1.zip](archive/r1.zip)，取消的旧模型目录另移入 `archive/r1-obsolete/`；它们不再属于当前导出。

R2.1 食物标签适配已通过 `processResources --offline`；本轮只改素材，未重复构建游戏，也未执行 Blockbench 应用导入、Minecraft 实机喂食、新模型接入、身体挂接或动态运行验收。技术核对与独立审查均不代替用户标准确认。
