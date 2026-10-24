# 预览与素材检查

生成页面统一放在 `.local/previews/`，入口为 `.local/previews/index.html`；模型与声音查看器位于 `models/`。预览只读游戏资源，不运行游戏 AI。

## 生成与查看

在仓库根目录执行：

```sh
python scripts/build_preview_hub.py --render
python scripts/serve_review.py --port 8766
```

`--render` 强制重建模型缩略图；不带它可复用输入指纹一致的缓存。照料设施、宠物用品款式图（`pet-goods/`，与原版方块并排，需本机原版客户端 jar，见[照料道具制作源](../standards/yukkuri-library/drafts/care-props/README.md)）和襁褓身上外观（`swaddle/`，`drafts/care-props/preview_wear.py`）、野生巢与储粮堆（`wild-nests/`，`drafts/care-props/preview_wild_nests.py`，含帽船储粮堆三档）每次从当前打包网格与贴图重建。服务仅监听本机；手动访问 `http://127.0.0.1:8766/.local/previews/index.html`。页面效果由用户手工查看。

## 内容与边界

入口按游戏系统分类，可搜索、筛选角色、动作片段、物品、声音、试玩入口和预览缺口。基础网格、物品图标和真实场景效果分别标注；缺少实例渲染时明确留空，不用旧拼图、粒子贴图或通用动作替代。

`preview/hub/categories.json` 是分类配置：游戏系统、动作与物品匹配规则、专项预览缺口在此维护。游戏规则和验收状态仍分别在所属专题和待办维护。缺口说明只描述预览器覆盖范围，不代表玩法未实现。

`build_preview_library.py` 从运行形态登记、物品注册和 `museum_scenarios.S` 建立目录。`preview_inventory.py` 读取当前 Java 注册形式（字面 ID、模块辅助方法和枚举），排除未注册的残留模型；旧存档兼容物品由分类配置排除。新增注册写法需扩展解析器，无法确认的本地化模型记入 `.local/reports/preview-library.json`。已注册但没有可用模型的物品保留为外观缺口。试玩入口直接读取场地和观察要点，不复制场景表。

动作目录只收录资源中存在且在非博物馆 Java 代码中找到字面引用的片段；无法解析的动态引用记入报告，不能据此认定游戏不使用该动作。片段存在或被引用都不等于完整行为已实现。单体播放器不模拟目标、碰撞、道具和身体状态附加渲染；捕食种、缩壳、家油片段分别选对应形态演示。角色支持旋转、线框、慢放、时间轴和逐帧查看；音频直接读取当前对白与声音登记。

`recent.json` 只维护随总入口可靠重建的图片内容。生成器输出必须由 `build_preview_hub.py` 本次运行产生或按输入指纹校验，再进入允许发布的媒体集合；只有文件存在的旧图片和内嵌数据网页不再自动收录。接入新专项时同时接入其生成器、完整输入指纹和展示边界，再移除对应预览缺口。制作源和原始参考不随撤下展示而删除。

`actions.json`、`childhood-life-actions.json` 等旧离线编排仍可用于制作参考，但总入口不再生成或发布这些场景，避免用安排好的动作路线代表现有行为。专项制作方法在各制作源说明维护；重新生成旧脚本产物本身不代表可以恢复为当前场景。实际玩法统一通过仓库根目录的“启动动作博物馆.cmd”观察。

## 缓存与本地意见

模型缩略图按索引、渲染器和贴图指纹复用，记录在 `.local/previews/_work/model-preview.sha256`。物品基础网格按网格、贴图与渲染脚本指纹复用，放在 `library/items/`；原生方块元素由 `preview_block_model.py` 每次重建。依赖原版贴图时读取本机 `MCYUKKURI_VANILLA` 缓存，只复制所需图标到本地预览，不打入游戏资源。

分类缺口、未确认的动作／物品资源和缺失渲染输入写入 `.local/reports/preview-library.json`。模型检查意见保存在当前浏览器，按资源指纹区分；导出 JSON 不改变游戏资源或标准素材认可状态。
