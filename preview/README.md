# 预览与素材检查

生成页面统一放在 `.local/previews/`，入口为 `.local/previews/index.html`；模型与声音查看器位于 `models/`。生成内容是离线演示，不修改游戏资源，也不运行游戏 AI。

## 生成与查看

在仓库根目录执行：

```sh
python scripts/build_preview_hub.py --render
python scripts/serve_review.py --port 8766
```

`--render` 重建模型索引、缩略图、动作 GIF 和总览图；不带它可复用有效缓存。照料道具每次从当前运行网格与贴图重建，也可单独运行 `python scripts/build_preview_props.py`，输出到 `.local/previews/care-current/`。服务仅监听本机，不自动打开浏览器。手动访问 `http://127.0.0.1:8766/.local/previews/index.html`。单独更新模型索引可运行 `python scripts/build_review_catalog.py`。

帽船分镜需要现有 Minecraft 资源 JAR 提供原版木棍、苹果贴图；依次运行以下命令后重建总览：

```sh
python standards/yukkuri-library/motions/variants/lore/aquatic_preview.py --minecraft-resources PATH_TO_CLIENT_JAR
python standards/yukkuri-library/motions/variants/lore/aquatic_family_preview.py --minecraft-resources PATH_TO_CLIENT_JAR
python standards/yukkuri-library/motions/variants/lore/first_voyage_preview.py --minecraft-resources PATH_TO_CLIENT_JAR --quick
python standards/yukkuri-library/motions/variants/lore/hat_motion_preview.py --minecraft-resources PATH_TO_CLIENT_JAR
python art/props/badges/preview.py
python scripts/build_preview_hub.py --render
```

商店、徽章与宠物预览的完整生成顺序见[商店说明](../art/structures/human-world/README.md)：先生成 UI、动作分镜，再运行场景脚本。

## 内容与边界

集中入口按场景自动播放对应动作，点击场景同步切换画面。`preview/hub/recent.json` 定义预览主题、素材入口与分镜说明；纯图库主题自动转为可选择的静帧，缺失图片、演示页及空主题不发布。总入口只登记选用的主题媒体，不扫描收录整个本地目录；过时对比和研究参考不列为当前预览。专项图片仍需由各自制作源生成，重建入口不会刷新这些图片。`actions.json` 为离线动作指定角色、动作、道具和镜头；`childhood-life-actions.json` 保存幼年照料、爬跳及帽沿排泄分镜。场景路线只用于演示编排。

`build_preview_hub.py` 使用当前运行资源生成全部形态与声音索引，并核对模型目录与运行登记一致。模型页支持旋转、线框、动作选择、慢放、时间轴和逐帧查看；声音页可按角色、场景和文字筛选播放。模型查询可用 `?model=marisa_tsumuri&action=shell_retract` 预选形态与动作。单体查看器不模拟多角色行为；多角色亲子、帽船与初航分镜由场景配置离线编排，不运行 Minecraft 碰撞、水流或救援 AI，因此不能证明游戏内行为自然触发。

没有专属动作的继任、库存变化等用状态图说明；对白可试听并显示字幕，纯字幕条目没有对应音频，捕食种字幕不表示新增配音。帽船剖视只展示帽沿下的身体和照料位置，游戏中的帽体仍完整。

帽船动作页面模板为[hat_motion_preview.html](../standards/yukkuri-library/motions/variants/lore/hat_motion_preview.html)。`preview/hub/` 内的 `recent.json`、`actions.json`、`childhood-life-actions.json`、`shop-badge-pets-actions.json` 保存主题和动作配置；`index.html`、`shop-badge-pets.html`、CSS 与 JS 是页面源。模型/声音索引由 `scripts/build_review_catalog.py` 从运行资源生成。

## 缓存与本地意见

`build_preview_actions.py` 按模型几何、贴图和动作指纹复用动作缓存；模型缩略图按索引、渲染器和贴图指纹复用，指纹记录在 `.local/previews/_work/model-preview.sha256`。临时栅格与任务文件放 `_work/`；各主题输出放 `.local/previews/<主题>/`。素材检查意见保存在当前浏览器本地，并按源资源指纹区分；导出 JSON 供讨论，不会改变游戏资源或标准素材认可状态。
