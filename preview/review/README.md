# 当前模型与声音查看器

这里保留查看器源码。生成页面、模型索引和缩略图统一位于 `.local/previews/models/`，由[集中预览](../hub/README.md)进入。

```sh
python scripts/build_preview_hub.py --render
python scripts/serve_review.py --port 8766
```

手动打开服务输出的地址。服务仅监听 `127.0.0.1`，不自动打开浏览器。Windows 也可使用根目录 `打开素材检查.cmd`；命令行仅启动时加 `scripts/start_review.ps1 -NoOpen`。

模型页读取当前运行资源的 19 种形态、贴图、骨架、位置适配和动作修订，支持旋转、线框、动作选择、慢放、时间轴和逐帧步进。家油、灵胖与不足形态保留各自体型。`pose.js` 与离线缩略图共用姿态求值逻辑；Three.js 来自仓库本地依赖。

模型链接可使用 `?model=marisa_tsumuri&action=shell_retract` 直接选择形态与动作。这里只展示单体模型；帽船亲子与初航的多角色分镜在集中预览的“本次更新”中，不由单体查看器模拟游戏 AI。

声音页读取当前对白和声音注册，可按角色、场景和文字筛选，逐条或连续试听。纯字幕条目不列入可播放音频，捕食种新增字幕不表示新增独立配音。

检查意见保存在浏览器本地，并按源资源指纹区分；导出 JSON 可带回讨论。记录不会自动修改游戏资源或标准素材的认可状态。

`build_review_catalog.py` 重建当前索引与页面，`build_preview_hub.py --render` 还会离线生成缩略图。旧的 `/preview/review/index.html` 服务入口自动转到集中预览。页面交互和游戏内表现由使用者手动查看；生成脚本与终端检查不操作浏览器。
