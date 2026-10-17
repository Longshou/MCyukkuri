# 美术与文字参考库

[Wiki](../README.md) · **[游戏参考库](../reference-library/README.md)** · [实体／情景／情绪筛选](../reference-library/gallery.html) · [制作归纳](../reference-library/production-briefs.md)

当前有效参考为 **177 张图片、41 张文字卡**，图片覆盖 **45 个来源入口**。先按要做的实体和情景查找，再叠加情绪、制作目标、画风或精选条件。每条图片注明具体画格和游戏用途，作品目录作为出处入口。

2026-10-17 新增宠物生活选页 25 张、有效图 17 张及文字卡 4 张，见[宠物与襁褓](../pet-life.md)。同日继续新增野外与交互选页 23 张、有效图 14 张及文字卡 4 张，见[野外与交互](../wild-life.md)和[树叶襁褓](../leaf-swaddle.md)。全库现存 416 张图片；下段保留 10 月 15 日扫图范围。

2026-10-15 已按当前游戏范围扫过 331 张存档图的联系表，入选 146 张；185 张本轮未选。另有 37 张随范围外来源撤出并停止采集。总览不等于细字核读，未选也不一定是画质差，可能是告知页、重复信息或没有对应的制作问题。逐页状态见[整理进度](../reference-library/context-and-provenance.md)。

优先查看 **[38 张美术精选](../reference-library/quality-picks.md)**。当前 47 张有效图有原文件质量记录，其中 9 张仅作补充；尺寸、画风、清楚的部位和观看限制分别登记。筛选器默认展示全部有效图片并将精选排前，可开启“只看美术精选”。

| 查找方向 | 能回答的制作问题 |
| --- | --- |
| [实体](../reference-library/entities.md) | 哪个角色、已纳入形态或道具？头发、饰物、窝、帽船分别怎么做？ |
| [情景](../reference-library/scenarios.md) | 进食、照料、睡眠、教学、搬运、交往或避险时怎么表现？ |
| [情绪](../reference-library/emotions.md) | 喜悦、安心、期待、困惑、恐惧等状态如何区分？ |
| [制作目标](../reference-library/production.md) | 需要模型、动作、表情、形变、道具、场景还是事件依据？ |

同图可跨标签检索，原文件只保存一份。小说补动机、先后过程与条件；漫画补可见姿态和接触点。[跨来源核对](../reference-library/cross-checks.md)保留不同作者的异说，同作改编与转载不重复算独立见证。

## 出处索引

[博客出处](blog-sources.md) · [当前范围内图集出处](ehentai/README.md) · [24 张文字卡](../novel-reading/README.md) · [早期画面观察](visual-guide.md) · [建模调研素材](game/README.md)

原始存档共 45 个图片来源入口、368 个文件，80,221,110 字节（约 76.5 MiB）、366 个不同哈希。它包含未选与撤出的材料，不等于有效参考数量；三个入口共用同一告知页。旧文件保留用于出处和历史链接追溯，撤出的种类不建新内容卡。来源 URL、原页序、尺寸与哈希见两个图片 manifest。

## 维护命令

在仓库根目录使用 Python 3.10+ 与 Pillow：

```powershell
python scripts/wiki_reference_library.py check
python scripts/wiki_ehentai_reference.py check
python scripts/wiki_reference_topics.py build
python scripts/wiki_reference_topics.py check
# 仅在需要补充指定来源时联网
python scripts/wiki_ehentai_reference.py fetch --source EH22
```

逐图分类以 `../reference-library/game-annotations.json` 为准：实体、情景、情绪、制作目标、画格和用途必须来自实际扫图。未入选材料登记明确状态。`quality-selection.json` 保存清晰度与画风复核，`classifications.json` 只维护旧主题网址；文字原始摘要在 `../novel-reading/cards.json`。

新增文件未写用途或队列状态时检查会报错。抓取工具跳过 `collect: false` 的来源，显式请求已撤出的图集会在联网前停止；不登录或操作浏览器。缓存与联系表放 `.local/`。

图片保留原字节与署名，站点转换文件不冒称作者母版；参考图不直接成为游戏资源或已认可的标准美术。作品规则、编辑归纳和项目采用依照[编辑口径](../EDITORIAL_GUIDE.md)分别标示。
