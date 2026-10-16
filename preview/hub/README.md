# 集中预览

唯一生成入口：`.local/previews/index.html`。本次更新按场景自动播放对应动作，点击场景同步切换画面；当前模型动作和声音试听在同一页面切换；各主题的 PNG、GIF 与独立面板放在 `.local/previews/<主题>/`。所有生成产物均被 Git 忽略。

## 生成与查看

```sh
python scripts/build_preview_hub.py --render
python scripts/serve_review.py --port 8766
```

需要 Python、Pillow、numpy 和 Node.js。服务只监听本机，不自动打开浏览器。手动访问 `http://127.0.0.1:8766/.local/previews/index.html`。

`recent.json` 保存这一批更新的内容、基准提交和截止提交；更新批次时与源码一起维护。不要只改截止 SHA 而沿用旧内容。`build_preview_hub.py` 重新索引当前模型与音频、复制查看器，并生成动作 GIF、模型缩略图和动作总览图，不修改游戏资源。`actions.json` 为每段离线演示指定实际动作、角色、道具及固定镜头；`build_preview_actions.py` 根据当前几何、贴图和动作指纹缓存产物。场景路线为演示安排，不运行游戏 AI。

`childhood-life-actions.json` 保存贴嘴喂糜、首便照料、爬跳与帽沿排泄的主题分镜，由同一入口生成到 `.local/previews/childhood-life/`。帽船剖视只用于展示被帽沿遮住的身体与照料位置，不改变游戏中的完整帽体。

普通动作会自动生成。首次生成或清空本机产物后，尚未生成的帽船分镜会明确显示待生成提示；缺失的动画和图库项不会展示。帽船分镜复用标准库现有脚本，原版客户端 JAR 仅用于读取木棍、苹果贴图：

```sh
python standards/yukkuri-library/motions/variants/lore/aquatic_preview.py --minecraft-resources PATH_TO_CLIENT_JAR
python standards/yukkuri-library/motions/variants/lore/aquatic_family_preview.py --minecraft-resources PATH_TO_CLIENT_JAR
python standards/yukkuri-library/motions/variants/lore/first_voyage_preview.py --minecraft-resources PATH_TO_CLIENT_JAR --quick
python standards/yukkuri-library/motions/variants/lore/hat_motion_preview.py --minecraft-resources PATH_TO_CLIENT_JAR
python art/props/badges/preview.py
python scripts/build_preview_hub.py --render
```

没有专属动作的继任、库存变化等以状态图呈现；普通对白直接提供音频和对应字幕，捕食种新增条目明确标为仅字幕。不要用静态模型或无关动作充当玩法演示。

`hat-motion/` 展示帽檐赤油撑稳、翻覆落水和危机争位挤落，提供三段 GIF、对应静帧、六格分镜、七段动作图及独立页面。它读取当前模型和制作源导出的动作，采用实际帽檐与船体转角；离船后的轨迹、拥挤过程和救援情景为离线演出示意，不运行 Minecraft 的碰撞、水流或救援 AI，也不代表自然触发已经实测。页面模板位于世界观动作制作目录的 `hat_motion_preview.html`。

## 维护

- 同一主题覆盖当前产物，删除已经被替代的图片、动图和临时查看页；不另存多套“最终版”。
- 页面模板与制作脚本留在 `preview/` 或原素材包；供制作读取的 HTML 数据、模型、贴图和原始参考素材不能当成预览删除。
- 渲染缓存、运行日志和本机清理清单放在 `.local/previews/_work/`、`_cache/`、`_server/`、`_maintenance/`；HTTP 服务不对外提供这些目录。
- 通过终端语法检查、资源路径检查、离线图片和只读 HTTP 检查验证。禁止为预览启动浏览器自动化或本机 Java 编译。
