# 集中预览

唯一生成入口：`.local/previews/index.html`。本次更新、当前模型动作和声音试听在同一页面切换；各主题的 PNG、GIF 与独立面板放在 `.local/previews/<主题>/`。所有生成产物均被 Git 忽略。

## 生成与查看

```sh
python scripts/build_preview_hub.py --render
python scripts/serve_review.py --port 8766
```

需要 Python/Pillow 和 Node.js；帽船分镜还需要 numpy。服务只监听本机，不自动打开浏览器。手动访问 `http://127.0.0.1:8766/.local/previews/index.html`。

`recent.json` 保存这一批更新的内容、基准提交和截止提交；更新批次时与源码一起维护。不要只改截止 SHA 而沿用旧内容。`build_preview_hub.py` 重新索引当前模型与音频、复制查看器、生成模型缩略图和总览图，不修改游戏资源。

首次生成或清空本机产物后，尚未生成的分镜会暂用当前模型缩略图，并显示提示；缺失的动画和图库项不会展示。帽船分镜复用标准库现有脚本，原版客户端 JAR 仅用于读取木棍、苹果贴图：

```sh
python standards/yukkuri-library/motions/variants/lore/aquatic_preview.py --minecraft-resources PATH_TO_CLIENT_JAR
python standards/yukkuri-library/motions/variants/lore/aquatic_family_preview.py --minecraft-resources PATH_TO_CLIENT_JAR
python standards/yukkuri-library/motions/variants/lore/first_voyage_preview.py --minecraft-resources PATH_TO_CLIENT_JAR --quick
python art/props/badges/preview.py
python scripts/build_preview_hub.py --render
```

## 维护

- 同一主题覆盖当前产物，删除已经被替代的图片、动图和临时查看页；不另存多套“最终版”。
- 页面模板与制作脚本留在 `preview/` 或原素材包；供制作读取的 HTML 数据、模型、贴图和原始参考素材不能当成预览删除。
- 渲染缓存、运行日志和本机清理清单放在 `.local/previews/_work/`、`_cache/`、`_server/`、`_maintenance/`；HTTP 服务不对外提供这些目录。
- 通过终端语法检查、资源路径检查、离线图片和只读 HTTP 检查验证。禁止为预览启动浏览器自动化或本机 Java 编译。
