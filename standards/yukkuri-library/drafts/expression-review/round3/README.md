# 表情第三轮

本目录保留第三轮候选源，供第四轮比较和追溯；不再是当前展示版本。第三轮 N020、N033 候选已被用户否定，N026 的加强泪花已在第四轮反馈中确认。当前入口见 [第四轮说明](../round4/README.md)与[逐表情比较](../batch-16-40.html)。

`candidates.json` 保存候选配方，`parts/` 保存候选像素，均未设为默认标准。制作脚本为 [review_expression_round3.py](../../../tools/review_expression_round3.py)，页面模板为 `review-template.html`。比较基线为 `expressions/component-review/before-n016-n040-round3.json`。N037 原版像素取自此前 `pending-expressions.html` 中该项的 `before`，即部件试用前版；恢复眼口、泪帧并撤销后加的泪光位移。

在素材库目录运行：

```sh
python tools/review_expression_round3.py
python tools/sync_expression_exports.py teary_pant_b teary_tongue_b horrified horrified_b tongue_cry tongue_cry_b dead_tongue
python tools/build_expression_round3_review.py
```

第一条脚本应用本轮已确定的选择，后续新一轮不得拿它覆盖新选择。第二条只同步指定表情的图集、预览内嵌图集、局部几何偏移及状态；复用已导出的模型和 UV，不重建身体、骨架与动画。共用表情同步到标准库六个角色的 `assembled/`；Minecraft 接入快照和其他资产包不在本轮更新范围内。

确认记录和指纹位于 `expressions/component-review/n016-n040-decisions.json` 的 `confirmed_versions`。第三轮检查见 [历史检查记录](../../../archive/expression-review-round3-report.json)，不证明第四轮通过。

## N033 参考

- 对象：失去高光／空洞眼神。检索词：`目 ハイライトなし 描き方 虚ろな目 イラスト`、`site:blog.livedoor.jp/yukkuri_anko 非ゆっくり症`。使用网页搜索及浏览器核读。
- 已见图：[LonicGirl《シンプルな目の描き方》](https://tips.clip-studio.com/ja-jp/articles/10274)，章节“目 6”，原图标识 `14088e601f9480b42e475590b27a8e3d`。画面眼内无白色反光点，虹彩上部深紫灰、下部浅灰粉，未画出清晰独立瞳孔。正文还讨论半垂眼睑。这里只借鉴明暗组织，未复制原图像素。
- 原创转译：A 用三档灰褐色；B 加弱瞳影；C 压低眼睑；D 减为两档深灰。当前眼图只有 15×12 像素，以色块分层替代细腻渐变；四版使用相同嘴和眉。
- 博客《非ゆっくり症詐欺》本轮访问未取得有效漫画图；取得的图像是站点图标，未作为本轮造型依据。此前漫画核读不能冒充本轮已见图。

核读截图放项目 `.local/reference/expression-round3/`，不进入正式贴图或导出。
